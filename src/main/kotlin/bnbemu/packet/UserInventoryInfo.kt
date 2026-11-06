package bnbemu.packet

import bnbemu.netty.PKT
import bnbemu.netty.toBA

// ============================================================================
// S→C op 413: 玩家背包物品全量槽位初始化 (進頻道握手推送 #1, C→S 81 後, seq=100, 614B)
// 说明“背包有多少格、多大容量”。UserInventory才是说明“角色身上装备了什么”
// ============================================================================
// 逆向確認:
//   CA.exe 0x00503829 (CStage::OnPacket) -> 0x005016A0.
//   處理類: TSingleton<CUserInventoryMan> (0x00993E68, 虛表 0x008B766C)
//          與 TSingleton<CUserItemMan> (0x00993D58).
//   欄位結構:
//     Encode1: slot (0=主背包)
//     Encode4: unk (0)
//     8B raw bytes: 簽名 / 時間戳 (88 95 ae 63 d6 2c c4 91)
//     Encode1: itemCount (50 槽)
//     50 × [Encode4(itemId) + Encode4(param1) + Encode4(param2)] (每條 12B, 共 600B)
//   總長度: 1 + 4 + 8 + 1 + 600 = 614 字節.
//   客戶端解析後將 50 個槽位逐一寫入 CUserInventoryMan, 並校驗 CUserItemMan 中的道具實體.
data class InventorySlot(
    val itemId: Int = 10007,
    val param1: Int = 0,
    val param2: Int = 0
)

fun UserInventoryInfo(
    slot: Int = 0,
    unk: Int = 0,
    signature: ByteArray = DEFAULT_INVENTORY_SIGNATURE,
    slots: List<InventorySlot> = DEFAULT_INVENTORY_SLOTS
) = PKT {
    Encode1(slot)
    Encode4(unk)
    EncodeBuffer(signature)
    Encode1(slots.size)
    for (s in slots) {
        Encode4(s.itemId)
        Encode4(s.param1)
        Encode4(s.param2)
    }
}

private val DEFAULT_INVENTORY_SIGNATURE = "8895ae63d62cc491".toBA()

// 50 个 itemId=10007 槽位 = 官方抓包原样（cap/chan_s2c_op413_616B_0.bin）。
// ⚠️ 它们不是"50 件物品"，而是**背包等级**
//   CUserInventoryMan 收 413 时逐条 sub_677912(itemId) 查 op 60 物品定义 → 查得到才进
//   sub_66A017 的物品应用流程；10007 的 ItemDef = [category 13, param 1, skinId 50]，
//   命中 category==13 && param==1 分支 → sub_663F28(CUserDynamicInfo, playerIdx, min(skinId, 等级+1))
//   即每一条都把该玩家的背包等级 +1（上限 skinId=50）。
//   背包容量 = CUserInventory::GetBagSize (0x659361) = 8 × CUserDynamicInfo[+492][playerIdx]。
//   → 等级恒 0 时容量为 0，而 IsBagFull (0x659520) 判定是「已用 >= 容量」，
//     于是商城购物篮购买前检查 (0x6A3722，文案 BasketDlgBagIsFull/BasketDoYouNeedBag)
//     恒弹 "Your item bag is full. Purchase an item bag?"。
//   → **本包必须排在 op 198 之后下发**：op 198 的 ApplyEquippedInventory (0x6757A6 →
//     0x673CF4) 会把等级覆盖回 0（实测：413 在 198 之前时运行时读到 dyn+0x1EC = 0，
//     挪到 198 之后 = 50、容量 400，商城购买不再提示背包已满）。
//   → 另: op 60 ItemDef 也必须先于本包，客户端按 itemId 查定义表，查不到整条丢弃。
private val DEFAULT_INVENTORY_SLOTS = List(50) { InventorySlot() }
