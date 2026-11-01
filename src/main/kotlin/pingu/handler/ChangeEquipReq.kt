package pingu.handler

import pingu.netty.Decode1
import pingu.netty.Decode2
import pingu.netty.Decode4
import pingu.netty.PKTHandler

/**
 * C->S op 273: 玩家换装/装备穿戴变更同步请求 (ChangeEquipReq)
 *
 * 逆向确认 (CA.exe EN v18):
 * - 发包来源与触发场景 (action):
 *   - 0: 房间/全局按钮变更 (CGlobalButtonDlg 0x0048D7FE)
 *   - 1: 商城选角界面变更 (CShopStageNew 0x005CDA65)
 *   - 2: 公会穿戴变更 (CShopStageNew 0x005D5E01)
 *   - 3: 个人资料卡 (My Page) 关闭时同步穿戴 (CMyPageDialog 0x005DE32F)
 *   - 5: 背包直接穿脱装备触发 (CInventoryWndStrategy 0x0065800C)
 *   - 6: 头像/形象变更 (0x005F1D6C)
 * - 关键触发与过滤机制:
 *   客户端每次检测到 CUserInventoryMan (0x00993E68) 中的物品脏标志位 ([edi + 0x84]) 时触发。
 *   若变动数量为 0 (0x0065803D test ax, ax)，客户端在 0x00658042 处直接销毁包放弃发送；
 *   仅在实际发生装备穿脱时，才组装本包发送给服务端同步。
 * - 包体结构:
 *   - Encode1(action): 触发场景 (0=Room, 1=CharSelect, 2=Guild, 3=MyPage, 5=Inventory, 6=Avatar)
 *   - Encode1(slotIndex): 玩家槽位 (通常为 0)
 *   - Encode2(modifiedCount): 变动道具数量
 *   - repeat(modifiedCount) {
 *       Encode1(equipSlot): 装备穿戴槽位
 *       Encode4(itemId): 装备的物品 ID (卸下装备时为 0)
 *     }
 *   - Encode1(sessionCount): 会话/玩家数
 *   - Encode1(flag): 附加状态标志 (通常为 0)
 *
 * 日志样本解析:
 *   59 5A A5 68 CE 96 CA 5B B7 5B 5A ->
 *   action=3 (MyPage), slotIndex=0, modifiedCount=1, equipSlot=0x94 (148), itemId=2082,
 *   sessionCount=1, flag=0. (刚好 11 字节完全匹配)
 */
data class EquipChangeItem(val equipSlot: Int, val itemId: Int)

val ChangeEquipReq = PKTHandler { c ->
    val action = Decode1
    val slotIndex = Decode1
    val count = Decode2
    val items = (0 until count).map {
        val equipSlot = Decode1
        val itemId = Decode4
        EquipChangeItem(equipSlot, itemId)
    }
    val sessionCount = Decode1
    val flag = Decode1

    // TODO: 持久化更新对应槽位玩家的穿戴装备数据
}

/*
  在客户端任何涉及玩家穿脱装备、切换形象的场景下（个人资料卡 My Page 退出、背包穿脱、商城选角、房间等），客户端通过 CUserInventoryMan 收集发生变更的物品（若变动数为 0 则直接丢弃放弃发包），发出 **C→S op 273 (0x111)**：
  - body = E1(action) + E1(slotIndex) + E2(count) + repeat(count) { E1(equipSlot) + E4(itemId) } + E1(sessionCount) + E1(flag)（共 11 字节基准）。
  - action 枚举：0=房间全局按钮换装 (CGlobalButtonDlg 0x48D7FE)，1=选角换装 (CShopStageNew 0x5CDA65)，2=公会换装 (CShopStageNew 0x5D5E01)，3=个人资料卡 My Page 关闭退出 (CMyPageDialog 0x5DE32F)，5=背包穿脱装备 (CInventoryWndStrategy 0x65800C)，6=头像变更 (0x5F1D6C)。
  - 日志样本 59 5A A5 68 CE 96 CA 5B B7 5B 5A 解密完全吻合：action=3 (MyPage), slot=0, count=1, equipSlot=0x94 (148), itemId=2082 (蓝蓝进阶卡), sessionCount=1, flag=0。
  - 回归测试 src/test/kotlin/pingu/netty/MyPageTest.kt。
 */