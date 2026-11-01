package pingu.packet

import pingu.netty.PKT
import pingu.netty.SendPacketBase

// ============================================================================
// 用户穿戴装备与选角判定机制 (S→C op 198) — CA.exe 0x00502F37 分发至 0x004F9416
// ============================================================================
// 1. CUserInventoryMan::ReadEquippedInventory (0x0067601D):
//    [Encode1(numInventories)] + N × [ [Encode4(itemCount)] + M × ItemEntry ]
//    每一项 Item 结构 (0x00672691):
//      - Encode4(itemId)
//      - Encode1(slot)
//      - Encode4(param)
//      - Encode2(strLen) + strBytes (Token，使用 encodeName 加密)
//      - 8 bytes (OLE Automation Date 时间戳)
//      - Encode4(duration)
//      - Encode1(status)
//
// 2. 用户穿戴装备包 (op 198 UserInventory) 与依赖顺序:
//    - CUserInventoryMan::ApplyEquippedInventory (0x006757A6) 对每件物品调用 CUserInventory::EquipItem (0x0065C81A):
//      - 0x0065C84D: 先调用 0x00677912(itemId) 在 op 60 (ItemDef) 表中查找物品定义 (2083 类别为 0x24 = 36)；
//      - 0x0065C85F: 调用 0x0067884E(36) 将类别 36 映射为穿戴槽位大类 9 (type=9)；
//      - 0x0065C88B (关键拦截点): 当 type != 4 时，客户端调用 CUserInventory::FindOwnedItemById (0x006595C4)，
//        在玩家已拥有的背包物品向量 [esi + 8]..[esi + 0xC] 中按 itemId (2083) 查找对应的 0xC8 字节物品对象！
//        若背包向量为空或找不到该物品，在 0x0065C897 处直接 return 0 失败退出，装备槽 [esi + 0xBBA0] 保持为空！
//      - 只有当 [esi + 8] 背包向量中已经存在 2083 时，0x0065C81A 才会继续走到 0x0065CE35，将其拷贝到
//        角色升级卡装备槽 [esi + 0xBBA0] (对应 type=9, subSlot=0)，完成激活。
//    - 蓝妹妹进阶卡真实参数: 在 op 60 (ItemDef) 中，蓝妹妹进阶道具卡为 itemId = 2083，Category = 36 (0x24)，TargetCharID = 4 (Marid / 蓝蓝)。
//    - 先后依赖顺序: 客户端必须遵循 先有 op 60 物品定义 → 再由 op 320 写入背包 [esi + 8] → 再由 op 198 穿戴到 [esi + 0xBBA0]。
//
// 3. 房间选角 UI 解锁判定 (0x005CE53D vs 0x005CE594):
//    - 切换到基础角色 4 (Marid) 时，0x005CE53D 调用 CStockContainer::FindCharacterStock(4) (0x006646AE)
//      校验 op 65 (ShopCatalog) 中是否存在对应商品 (Category == 0x10, Count >= 1, ItemDef->TargetCharID == 4)；
//    - 随后 0x005CE594 调用 CUserInventory::GetEquippedItem(userIdx=0, category=36, subSlot=0) (0x006589EF)
//      读取 [esi + 0xBBA0] 的激活标志:
//      - 若已装备 (非 NULL): 走 0x005CE5B1，蓝妹妹 (LuxuryMarid / Posh Evie) 正常亮起可选；点击后客户端发送 C→S op 87 SelectBomberReq(slot, bomberId=10)；
//      - 若未装备 (返回 NULL): 走 0x005CE6BD 显示带购买提示的灰色按钮。
// ============================================================================

val DEFAULT_EXPIRE_DATE: ByteArray = byteArrayOf(
    0x88.toByte(), 0x95.toByte(), 0xAE.toByte(), 0x63.toByte(),
    0xD6.toByte(), 0x2C.toByte(), 0xC4.toByte(), 0x91.toByte()
)

val DEFAULT_PRACTICE_EXPIRE_DATE: ByteArray = byteArrayOf(
    0x3B.toByte(), 0xA6.toByte(), 0x4A.toByte(), 0x48.toByte(),
    0xD6.toByte(), 0x2C.toByte(), 0xC4.toByte(), 0xA4.toByte()
)

class UserInventoryEntry(
    val itemId: Int,
    val slot: Int = 15,
    val param: Int = 1,
    val token: String = "",
    val expireDate: ByteArray = DEFAULT_EXPIRE_DATE,
    val duration: Int = 240000,
    val status: Int = 255,
) {
    fun Encode(p: SendPacketBase) {
        p.Encode4(itemId)
        p.Encode1(slot)
        p.Encode4(param)
        val tok = if (token.isEmpty()) ByteArray(0) else encodeName(token)
        p.Encode2(tok.size)
        if (tok.isNotEmpty()) {
            p.EncodeBuffer(tok)
        }
        p.EncodeBuffer(expireDate)
        p.Encode4(duration)
        p.Encode1(status)
    }
}

// 进频道大厅默认生效/穿戴道具列表 (包含蓝妹妹进阶卡 itemId 2083)
val DEFAULT_USER_INVENTORY_ITEMS = listOf(
    UserInventoryEntry(itemId = 1, slot = 15, param = 20, token = "Dao", status = 192),
    UserInventoryEntry(itemId = 10006, slot = 13, param = 1, token = "Item/ItemBag", status = 255),
    UserInventoryEntry(itemId = 2083, slot = 15, param = 1, token = "Item/CharDeco", status = 255), // 蓝妹妹 (LuxuryMarid / Posh Evie) 进阶卡
)

// 练习房默认生效/穿戴道具列表
val DEFAULT_PRACTICE_INVENTORY_ITEMS = listOf(
    UserInventoryEntry(itemId = 5, slot = 15, param = 20, token = "Boz", expireDate = DEFAULT_PRACTICE_EXPIRE_DATE, status = 192),
    UserInventoryEntry(itemId = 10006, slot = 13, param = 1, token = "Item/ItemBag", expireDate = DEFAULT_PRACTICE_EXPIRE_DATE, status = 255),
    UserInventoryEntry(itemId = 2083, slot = 15, param = 1, token = "Item/CharDeco", expireDate = DEFAULT_PRACTICE_EXPIRE_DATE, status = 255), // 蓝妹妹 (LuxuryMarid / Posh Evie) 进阶卡
)

fun UserInventory(
    inventories: List<List<UserInventoryEntry>> = listOf(DEFAULT_USER_INVENTORY_ITEMS, DEFAULT_USER_INVENTORY_ITEMS)
) = PKT {
    Encode1(inventories.size)
    inventories.forEach { items ->
        Encode4(items.size)
        items.forEach { it.Encode(this) }
    }
}

// 兼容别名
fun Unk198() = UserInventory()
