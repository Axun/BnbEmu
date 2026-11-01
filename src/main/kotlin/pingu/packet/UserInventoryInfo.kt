package pingu.packet

import pingu.netty.PKT
import pingu.netty.toBA

// ============================================================================
// S→C op 413: 玩家背包物品全量槽位初始化 (進頻道握手推送 #1, C→S 81 後, seq=100, 614B)
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
private val DEFAULT_INVENTORY_SLOTS = List(50) { InventorySlot() }
