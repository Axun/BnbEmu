package bnbemu.packet

import bnbemu.netty.PKT
import bnbemu.netty.SendPacketBase
import bnbemu.server.UserItemEntry
import bnbemu.server.EquipSlot

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
    val slot: Int = EquipSlot.CHARACTER,
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

// 进频道大厅默认生效/穿戴道具列表 (包含蓝妹妹进阶卡 itemId 2082)
val DEFAULT_USER_INVENTORY_ITEMS = defaultUserInventoryItems(0)

/**
 * 进频道时下发的默认穿戴三件套: 角色卡 + 道具栏 + 进阶卡。
 *
 * 角色卡跟随玩家当前选中的角色 ([bomber]): 否则大厅换角色后一进频道就被 op 198
 * 重建回皮蛋, `SendUserInfo` 的名片也跟着错。
 */
fun defaultUserInventoryItems(bomber: Int = 0): List<UserInventoryEntry> {
    val (charItemId, charToken) = when (bomber) {
        0 -> 1 to "Dao"
        1 -> 2 to "Diz"
        2 -> 3 to "Moss"
        3 -> 4 to "Melvin"
        4, 10 -> 2082 to "Marid"
        5 -> 5 to "Boz"
        6 -> 6 to "Ike"
        7 -> 7 to "Plunk"
        else -> 1 to "Dao"
    }
    return listOf(
        UserInventoryEntry(itemId = charItemId, slot = EquipSlot.CHARACTER, param = 20, token = charToken, status = 192),
        UserInventoryEntry(itemId = 10006, slot = EquipSlot.ITEM_BAG, param = 1, token = "Item/ItemBag", status = 255),
        UserInventoryEntry(itemId = 2082, slot = EquipSlot.CHAR_DECO, param = 1, token = "Item/CharDeco", status = 255), // 蓝妹妹 (LuxuryMarid / Posh Evie) 进阶卡
    )
}

fun UserInventory(
    inventories: List<List<UserInventoryEntry>> = listOf(DEFAULT_USER_INVENTORY_ITEMS, DEFAULT_USER_INVENTORY_ITEMS)
) = PKT {
    Encode1(inventories.size)
    inventories.forEach { items ->
        Encode4(items.size)
        items.forEach { it.Encode(this) }
    }
}

/**
 * 背包专属伪槽位。
 *
 * `CUserInventory::AddItem` = `sub_65D55F` (`0x0065D55F`) 的判定：
 * - 物品分类属于可穿戴集合 {17,13,16,2,33,9,20,36,41,42} → 走装备槽（`sub_65C81A`）；
 * - 其余分类（如 10 = Item/ShopDeco 装扮背景、14 = 水泡）：只有 `slot == -1`(0xFF)
 *   且映射类型不在 {4,6,7,12} 时才 **只 push 进背包向量 `[inv+8]`**，不占装备槽。
 * 这正是客户端在 C→S op 273 里对新入包物品上报的槽位值。
 */
const val BAG_ONLY_SLOT = EquipSlot.BAG

/**
 * 把服务端背包（[bnbemu.packet.UserItemEntry]）拼成 S→C op 198 的条目。
 *
 * ⚠️ op 198 是客户端**唯一**会整体重建背包的入口：
 *   `CUserInventoryMan::ReadEquippedInventory` (`0x67601D`) 先把两位玩家的背包/装备清空，
 *   再由 `ApplyEquippedInventory` (`0x6757A6`) 逐条 `sub_65D55F` 重建。
 *   所以服务端认账的背包必须放进这份清单，否则：
 *   商城里买完客户端自己塞进背包 → 一进频道被 op 198 清空 → 物品丢失。
 *   （S→C op 320 是商城专用的「单件商品入包」，且定义查的是商城商品管理器，
 *     在频道/大厅里查不到，不能用来持久化背包。）
 *
 * @param excludeIds 已经穿在身上的 itemId：这些条目由 [equippedInventoryEntries] 以真实
 *   装备槽下发，不能再以 0xFF 重复入包（否则客户端背包里会多出一条重复物品，
 *   而 op 198 是「全量真相」，重复条目会被当成两件不同物品分别落地）。
 */
fun bagInventoryEntries(
    items: List<UserItemEntry>,
    excludeIds: Set<Int> = emptySet(),
): List<UserInventoryEntry> = items
    .filter { it.itemId != 0 && it.itemId !in excludeIds }
    .map { item ->
        val def = ItemDef.find(item.itemId)
        UserInventoryEntry(
            itemId = item.itemId,
            slot = BAG_ONLY_SLOT,
            param = 1,
            token = def?.name ?: "",
            duration = 240000,
            status = 255,
        )
    }

// ============================================================================
// 装备槽位编码 (op 273 的 E1(equipSlot) 与 op 198 / op 73 的 E1(slot) 不是同一套)
// ============================================================================
// CA.exe 装备数组布局: 每个装备项 200 字节, 下标 = type*24 + subSlot
//   - 写入: `sub_65C81A` (`0x0065C81A`) 557216 行
//           `sub_4484B0(v29 + 116, a4 + 24 * v41)` —— 把「复合槽位」写进槽项 +116,
//           而槽项 +116 就是物品对象 +84 (`sub_44862D(item+84)` 读的同一字段)。
//   - 发出: C→S op 273 的序列化 `sub_659004` (`0x00659004`) 553095 行原样读 item+84 写 E1。
//   ⇒ op 273 的槽位 = 24*type + subSlot (蓝妹妹 2082 = 148 = 6*24+4, 实证吻合)。
//
// op 198 / op 73 的 slot 字段则只放 **subSlot**:
//   客户端 `sub_65D55F` (`0x0065D55F`) 自己用 `sub_67884E(category)` 算 type, 再取 `slot % 24`
//   (557643 行), 所以抓包里 2082 的 slot=4、角色卡 slot=15、道具栏 slot=13 都是 subSlot。
// ============================================================================

/** 每组装备的槽位数量 (客户端 24 个) */
const val EQUIP_SLOTS_PER_GROUP = 24

/**
 * category → 装备大类, CA.exe `sub_67884E` (`0x0067884E`) 的逐 case 镜像。
 * 0 表示该分类不可穿戴 (客户端 `sub_65C81A` 第一步就 `if (type == 0) return 0`)。
 */
fun equipGroupOfCategory(category: Int): Int = when (category) {
    1, 3 -> 8
    2, 9, 20, 33, 36, 41 -> 7
    4, 5, 6, 10, 18, 28, 29 -> 9
    12, 14 -> 10
    16 -> 6
    19 -> 5
    42 -> 4
    else -> 0
}

/**
 * 装备条目的「客户端定位键」= (装备大类, 子槽位)。
 *
 * 用大类参与键值是因为 type 由物品分类推导: 同一个 subSlot 在不同大类里是**不同槽位**
 * (例如道具栏 cat 13 的 subSlot 13 与装扮背景 cat 10 的 subSlot 13 互不影响)。
 */
fun equipSlotKey(itemId: Int, slot: Int): Pair<Int, Int> =
    equipGroupOfCategory(ItemDef.find(itemId)?.category ?: -1) to slot

/**
 * 把 C→S op 273 上报的装备槽 (复合槽位) 拼成 op 198 / op 73 的条目。
 *
 * slot = equips 的 key (复合槽位 24*type+subSlot)，这里只取 subSlot 下发,
 * 客户端会按 op 60 定义表里的 category 自行还原 type。
 *
 * @param equips 服务端记录的 装备槽 → itemId
 */
fun equippedInventoryEntries(equips: Map<Int, Int>): List<UserInventoryEntry> = equips
    .filterValues { it != 0 }
    .map { (compositeSlot, itemId) ->
        val def = ItemDef.find(itemId)
        UserInventoryEntry(
            itemId = itemId,
            slot = compositeSlot % EQUIP_SLOTS_PER_GROUP,
            param = 1,
            token = def?.name ?: "",
            duration = 240000,
            status = 255,
        )
    }

/**
 * 组装 S→C op 198 的完整清单 (进频道 / 商城退出重进大厅 / 背包查询都走这里)。
 *
 * 顺序与真实抓包一致: 先默认穿戴条目 (角色卡 / 道具栏 / 进阶卡), 再背包条目 (0xFF)。
 * 服务端 op 273 记录下来的装备 (背景 / 旗帜 / 边框 / 气泡 / 特效 …) 会以真实槽位**覆盖**
 * 同「大类+子槽位」的默认条目 —— 否则换角色卡或换背景时客户端会同时收到两条同槽条目。
 *
 * ⚠️ 这份清单就是客户端的全部真相 (`sub_67601D` 先清空再重建), 漏掉任何一件装备
 *    都会在回大厅时表现为「自动脱下」(背景被取消装备的根因)。
 */
fun inventoryEntries(
    defaults: List<UserInventoryEntry>,
    bag: List<UserItemEntry>,
    equips: Map<Int, Int>,
): List<UserInventoryEntry> {
    val equipped = equippedInventoryEntries(equips)
    val equippedIds = equipped.map { it.itemId }.toSet()

    val merged = LinkedHashMap<Pair<Int, Int>, UserInventoryEntry>()
    defaults.forEach { merged[equipSlotKey(it.itemId, it.slot)] = it }
    equipped.forEach { merged[equipSlotKey(it.itemId, it.slot)] = it }

    return merged.values + bagInventoryEntries(bag, equippedIds)
}
