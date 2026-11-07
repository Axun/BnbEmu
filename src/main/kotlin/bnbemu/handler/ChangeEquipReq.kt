package bnbemu.handler

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.server.UserItemEntry

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

/** 客户端用来表示「这件物品只在背包里, 没有装备槽」的伪槽位 */
const val BAG_SLOT = 0xFF

/** 一条装备变更记录的线上长度: E1(slot) + E4(itemId) */
private const val EQUIP_ITEM_LEN = 5

/** 合法装备槽上限 (客户端 CUserInventory 的装备区, 实测 0..0x94 都有使用) */
private const val MAX_EQUIP_SLOT = 200

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
    for (it in items) {
        if (it.equipSlot == BAG_SLOT || it.equipSlot > MAX_EQUIP_SLOT) {
            // 背包内容变化: 把新物品记进服务端背包, 下一次进频道用 op 320 补发
            if (it.itemId != 0 && user.items.none { e -> e.itemId == it.itemId }) {
                user.items += UserItemEntry(itemId = it.itemId)
                bagChanged = true
            }
        } else {
            // 装备槽变化: itemId == 0 表示卸下
            if (it.itemId == 0) user.equips.remove(it.equipSlot) else user.equips[it.equipSlot] = it.itemId
        }
    }
    if (bagChanged) {
        println("[ChangeEquipReq] 服务端背包已记录: ${user.items.map { it.itemId }}")
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
