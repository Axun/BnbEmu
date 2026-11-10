package bnbemu.handler

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.EQUIP_SLOTS_PER_GROUP
import bnbemu.packet.ItemDef
import bnbemu.packet.equipGroupOfCategory
import bnbemu.packet.room.SetPlayer
import bnbemu.server.UserItemEntry
import bnbemu.server.EquipSlot
import bnbemu.server.Room

/**
 * C->S op 273: 玩家换装/装备穿戴变更同步请求 (ChangeEquipReq)
 *
 * 逆向确认 (CA.exe EN v18):
 * - 发包来源与触发场景 (action):
 *   - 0: 房间/全局按钮变更 (CGlobalButtonDlg 0x0048D7FE)
 *   - 1: 商城选角界面变更 (CShopStageNew 0x005CDA35)
 *   - 2: 商城购买结果 (S→C op 202) 后的回执 (CShopStageNew 0x005D5E01 → 0x5D5DC4 分支)
 *   - 3: 个人资料卡 (My Page) 关闭时同步穿戴 (CMyPageDialog 0x005DE32F)
 *   - 5: 背包直接穿脱装备触发 (CInventoryWndStrategy 0x0065800C)
 *   - 6: 头像/形象变更 (0x005F1D6C)
 * - 包体结构 (由 `sub_659004` 0x00659004 决定):
 *   - E1(action): 触发场景
 *   - E1(playerIndex): 本地玩家槽位 (0/1)
 *   - E2(modifiedCount): 变动条目数 —— 注意是**后补写**的
 *     (sub_659004 先逐条写, 最后用 vtable+72 把计数回填到 a2[6] 即偏移 2)
 *   - repeat(modifiedCount) { E1(equipSlot) + E4(itemId) }   (每条 5 字节)
 *   - 可选尾部: 部分场景 (action 1/3) 还会追写 E1(在线玩家数) + E1(1);
 *     **action=2 (商城购买回执) 没有尾部**。
 *     因此这里按「读多少算多少」解析, 不再硬读尾部字段。
 * - equipSlot = 0xFF 的含义: 该物品只存在于背包向量 `[inv+8]`, 还没有装备槽
 *   (sub_659004 对背包尾部分支取 `sub_44862D(item+120)`, 新入包物品该字段未初始化 → 0xFF)。
 *   所以 0xFF 不能当装备槽, 应视为「背包内容变化」。
 *
 * ⚠️ 非 0xFF 的 equipSlot 是**复合槽位 = 24 × 装备大类 + 子槽位**:
 *   大类由物品分类推导 (`sub_67884E`: category 10 背景/旗帜/边框 → 9, 16 进阶卡 → 6,
 *   12/14 特效/气泡 → 10), 槽项 +116 (即物品 +84) 存的就是这个复合值,
 *   `sub_659004` 553095 行原样写进 E1。实证: 蓝妹妹 2082 上报 `0x94 = 148 = 6×24+4`。
 *   因此装扮背景 (category 10 → 大类 9) 的槽位是 216~239, **不是** 0xFF；
 *   旧实现用 `MAX_EQUIP_SLOT = 200` 卡它, 会把背景误判成背包物品 (背景回大厅被"自动脱掉"的根因之一)。
 *
 * 日志样本:
 *   1) `59 5A A5 68 CE 96 CA 5B B7 5B 5A` (11B, action=3):
 *      action=3, player=0, count=1, [(0x94, 2082)], tail=[1, 0]
 *   2) 商城买完 Bomb 655 后 (14B, action=2):
 *      `58 5A A5 6B CE 96 CA 5B B7 A5 A1 A4 FC 9A`
 *      action=2, player=0, count=2, [(0x94, 2082), (0xFF, 930000655)], 无尾部
 *
 * ⚠️ 历史坑: 旧实现无条件读尾部 `E1(sessionCount)+E1(flag)`, 遇到没有尾部的
 *    action=2 包会 IndexOutOfBoundsException（客户端一买完东西服务端就抛
 *    「業務邏輯錯誤 [ChangeEquipReq]」）。现已改为按剩余可读字节解析。
 */
data class EquipChangeItem(val equipSlot: Int, val itemId: Int)

/** 客户端 action 枚举 (见类注释) */
object EquipAction {
    const val ROOM = 0
    const val CHAR_SELECT = 1
    const val SHOP_PURCHASE = 2
    const val MY_PAGE = 3
    const val INVENTORY = 5
    const val AVATAR = 6
}

/** 一条装备变更记录的线上长度: E1(slot) + E4(itemId) */
private const val EQUIP_ITEM_LEN = 5

/**
 * 客户端用来表示「这件物品只在背包里, 没有装备槽」的伪槽位。
 *
 * 其余 0..0xFE 全部按复合槽位处理: 复合槽位 = 24 × 大类 + 子槽位, 大类 1..10
 * ⇒ 24..239 是实装区 (type 10 的 240..255 受 E1 截断, 属异常值), 没有「上限 200」这回事。
 *
 * ⚠️ 历史坑: 旧常量 `MAX_EQUIP_SLOT = 200` 把「> 200 一律当背包」写死, 于是
 *   装扮背景/旗帜/边框 (category 10 → 大类 9 → 216~239) 一上报就被塞进 `user.items`,
 *   永远进不了 `user.equips`, 也永远不会出现在 op 198 的装备清单里 →
 *   回大厅时背景被客户端清空重建后「自动取消装备」。
 */
private const val NON_EQUIP_SLOT = EquipSlot.BAG

/** 装备大类名 (仅用于日志可读性) */
private val EQUIP_GROUP_NAMES = mapOf(
    4 to "情侣/戒指", 5 to "19类", 6 to "进阶卡", 7 to "卡牌类",
    8 to "角色卡", 9 to "装扮/背景", 10 to "特效/气泡",
)

/** 把复合槽位翻译成日志可读文本, 例: `0xD9(217) → 大类9(装扮/背景) 子槽 1` */
private fun describeSlot(slot: Int): String {
    val group = slot / EQUIP_SLOTS_PER_GROUP
    val sub = slot % EQUIP_SLOTS_PER_GROUP
    val name = EQUIP_GROUP_NAMES[group]?.let { "($it)" } ?: ""
    return "0x${slot.toString(16).uppercase()}($slot) → 大类$group$name 子槽$sub"
}

/** 按 itemId 查 大类, 用于校验上报槽位与物品分类是否自洽 */
private fun groupOfItem(itemId: Int): Int {
    val cat = ItemDef.find(itemId)?.category ?: return -1
    return equipGroupOfCategory(cat)
}

val ChangeEquipReq = PKTHandler { c ->
    val total = readableBytes()
    if (total < 2) {
        println("[ChangeEquipReq] 包体过短 (${total}B), 忽略")
        return@PKTHandler
    }

    val action = Decode1
    val playerIndex = Decode1
    val count = if (readableBytes() >= 2) Decode2 else 0

    val items = ArrayList<EquipChangeItem>(minOf(count, 64))
    while (items.size < count && readableBytes() >= EQUIP_ITEM_LEN) {
        items += EquipChangeItem(Decode1, Decode4)
    }

    // 尾部字段按实际剩余字节读取 (action 1/3 有, action 2 没有)
    val tail = ArrayList<Int>()
    while (readableBytes() > 0) tail += Decode1

    println(
        "[ChangeEquipReq] action=$action player=$playerIndex count=$count " +
            "解析=${items.size} tail=$tail (payload ${total}B) " +
            items.joinToString { "slot=0x${it.equipSlot.toString(16)} item=${it.itemId}" }
    )

    val user = c.users.firstOrNull() ?: return@PKTHandler
    var bagChanged = false
    var equipChanged = false

    for (it in items) {
        if (it.itemId == 0) {
            println("[ChangeEquipReq]   slot=0x${it.equipSlot.toString(16)} item=0 → 忽略 (客户端跳过空记录)")
            continue
        }
        if (it.equipSlot == NON_EQUIP_SLOT) {
            // 背包内容变化: 新入包 或 装备卸下退回背包
            if (ItemDef.find(it.itemId)?.category == 14) {
                // 水泡皮肤只有在真实装备槽 (大类 10 → 0xF0~0xF8) 里才会被 op 100 的
                // f32 字段下发; 0xFF 只代表"买到了 / 在背包里", 对局内仍是默认水泡。
                println(
                    "[ChangeEquipReq]   ⚠️ 水泡皮肤 item=${it.itemId} 只进背包 (0xFF), " +
                        "未占装备槽 → op 100 的 f32 保持 0, 对局内仍是默认水泡"
                )
            }
            // 真实客户端卸下装备 (CA.exe sub_65CF4E 卸入背包向量, sub_659004 0x659045 跳过 item=0 的装备槽,
            // 仅在背包向量遍历中上报 slot=0xFF + itemId):
            // 若该物品此前正穿在装备槽中, 说明本次操作为「卸下」, 须从 user.equips 中移除!
            val equippedSlot = user.equips.entries.find { e -> e.value == it.itemId }?.key
            if (equippedSlot != null) {
                user.equips.remove(equippedSlot)
                equipChanged = true
                println(
                    "[ChangeEquipReq]   slot=${describeSlot(equippedSlot)} → 卸下 item=${it.itemId} (上报为 0xFF 退回背包)"
                )
            }

            if (user.items.any { e -> e.itemId == it.itemId }) {
                println("[ChangeEquipReq]   slot=0xFF item=${it.itemId} → 背包 (已存在, 跳过)")
            } else {
                user.items += UserItemEntry(itemId = it.itemId)
                bagChanged = true
                println("[ChangeEquipReq]   slot=0xFF item=${it.itemId} → 背包 (新入包)")
            }
        } else {
            // 装备槽变化: 记进 user.equips, 下一次 op 198 会按真实槽位重新下发
            user.equips[it.equipSlot] = it.itemId
            // 穿上后从背包列表里摘掉, op 198 的「背包条目」与「装备条目」不能同时有它
            val wasInBag = user.items.removeIf { e -> e.itemId == it.itemId }
            if (wasInBag) bagChanged = true
            equipChanged = true
            val wantGroup = it.equipSlot / EQUIP_SLOTS_PER_GROUP
            val gotGroup = groupOfItem(it.itemId)
            val mismatch = when {
                gotGroup == -1 ->
                    "  ⚠️ op 60 无该物品定义 → 客户端 sub_6757A6 查不到会整条丢弃"
                gotGroup in 1..15 && gotGroup != wantGroup ->
                    "  ⚠️ 大类不符(item category→$gotGroup, 槽位→$wantGroup)"
                else -> ""
            }
            println(
                "[ChangeEquipReq]   slot=${describeSlot(it.equipSlot)} → 装备 item=${it.itemId}" +
                    " (${ItemDef.find(it.itemId)?.name ?: "无定义"})" +
                    (if (wasInBag) " [从背包移到装备]" else "") + mismatch
            )
        }
    }

    if (bagChanged) {
        println("[ChangeEquipReq] 服务端背包: ${user.items.map { it.itemId }}")
    }
    if (equipChanged) {
        println(
            "[ChangeEquipReq] 服务端装备槽: " +
                (user.equips.entries.joinToString { (s, id) -> "0x${s.toString(16)}=$id" }
                    .ifEmpty { "(空)" })
        )
        // 房间阶段槽位外观刷新:
        // 客户端在房间内 (CSlotStage / CPrepareStage) 关闭背包或换装时, 本地 CSlotView 不会自动重绘,
        // 必须由服务端下发 S→C op 100 SetPlayer (CSetPlayerOnPreparePacket) 触发 sub_4F7022 -> sub_4F6E89,
        // 重新按 CSlotAccessoryPacket 三段清单 (背景/边框/特效/纸娃娃) 刷新槽位 UI 并清理脱下的装扮。
        val roomSlotIdx = Room.slots.indexOfFirst { it.user == user }
        if (roomSlotIdx != -1) {
            Room.BC(SetPlayer(roomSlotIdx, Room.slots[roomSlotIdx], user))
            println("[ChangeEquipReq] 房间槽位 $roomSlotIdx 外观已通过 SetPlayer (op 100) 广播刷新")
        }
    }
}

/*
  在客户端任何涉及玩家穿脱装备、切换形象的场景下（个人资料卡 My Page 退出、背包穿脱、商城选角、
  商城购买回执、房间等），客户端通过 CUserInventoryMan 收集发生变更的物品（若变动数为 0 则直接
  丢弃放弃发包），发出 **C→S op 273 (0x111)**：
  - body = E1(action) + E1(playerIndex) + E2(count) + repeat(count) { E1(equipSlot) + E4(itemId) }
    + 可选 E1(在线玩家数) + E1(1)（action=2 商城购买回执没有这两字节）。
  - action 枚举：0=房间全局按钮换装 (0x48D7FE)，1=选角换装 (0x5CDA35)，2=商城购买回执
    (CShopStageNew 处理 op 202 的 0x5D5E01 → 0x5D5DC4 分支)，3=个人资料卡关闭 (0x5DE32F)，
    5=背包穿脱 (0x65800C)，6=头像变更 (0x5F1D6C)。
  - equipSlot=0xFF 表示物品只在背包里（新入包物品槽位未初始化），不是装备槽。
  - 回归测试 src/test/kotlin/bnbemu/netty/MyPageTest.kt。
 */
