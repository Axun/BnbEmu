package bnbemu.packet.user

import bnbemu.netty.PKT
import bnbemu.packet.ItemDef

// ============================================================================
// S→C op 200：CUserInventoryMan 的「我的名片」槽位表（My Page slots，进频道握手推送 #3）
// ============================================================================
// 与 op 227 MyPageInfo（个人资料摘要）同一套 My Page 界面
//
// ── 客户端侧──────────────────────────────
//   大厅/频道分发器 op 200 → 0x004F81CA
//       → 0x00672C50  (CUserInventoryMan 槽位表读取器，本包体)
//       → 0x0067593D  (逐页复检)
//   槽位数组：0x00993E68（玩家物品/背包管理器单例）里由
//     `eh vector constructor iterator(this + 33121, 0x30, 16, ...)` 构造的 **16 个** 48 B 槽位，
//     按 [页 0..1][槽 0..7] 展平 → 2 页 × 8 槽，与 `sub_672C50` 的 `this + 96*page + 12*slot`
//     以及 `sub_672D7E` 的 `this + 384*page + 48*slot` 完全吻合。
//   单个槽位 48 B：
//     +0x00 (dword 33121) 物品定义 SN（0 = 空槽）
//     +0x0C (dword 33124) 占用/有效标志（读取器清 0、写入者置 1；具体语义未逐处证实）
//     +0x18 (dword 33127) 显示用模型名（std::string，SSO 16 B）
//   读取器 0x00672C50 的循环（= 本包体结构，逐字节可对回 cap）：
//     n = Decode1()                                   // E1 页数（= 玩家数）
//     if (n) for (page = 0; page < n; page++)
//              for (slot = 0; slot < 8; slot++) {     // 8 是硬编码常量
//                 itemId = Decode4();                 // E4
//                 name   = DecodeStr();               // 带 0x75DAF0 令牌变换的字符串
//                 if (itemId && CItemDef 里存在该 itemId) 才写进槽位
//              }
//   ⇒ **E1 是页数不是条目数**，每页恒 8 槽，无 per-entry 计数。
//   ⚠️ n > 2 会直接把 16 槽数组写越界（for 循环没有上界检查），服务端必须 clamp 到 2。
//
// ── 字段含义 ──────────────────────────────────────────────────────────────
//   page  = 玩家槽位序号（0 = 自己，1 = 对局里的另一位玩家），与 op 198/413 的两人份
//           背包、S→C op 274 的 Encode1(玩家槽位) 是同一套下标。客户端逐页用
//           `sub_671EF1(page, itemId)` 在这位玩家的背包里找该物品。
//   slot  = 0..7，客户端 My Page（MyInfo）的 8 个格子，图素名 `MPEmotionSlot0..7`
//           （sub_5DAF64 里 8 次 sub_75AB10(..., "MPEmotionSlot%d", i)），即「表情/动作」槽；
//           **slot 7 是情侣槽**：客户端画格子时 `if (sub_6C054E(2) || i < 7)`
//           只有特性 2 打开才画第 8 格；解除情侣（0x0069ABA1 / 0x0069ADCC 的 "LeaveCouple"
//           分支）会专门 `sub_672D7E(page, 7, 0, "")` 再发 C→S 274 把该槽清空。
//   name  = 物品定义表（S→C op 60）里的 `ItemDefEntry.name` 模型名，不是显示名/角色名；
//           抓包里 itemId 1 对应 "Dao"（皮蛋角色卡），与 op 60 的定义逐字一致。
//
// ── 抓包基准（cap/chan_s2c_op200_105B_0.bin, decoded_dump.txt:147, sq=106）────
//   E1(2) + 16 × [E4 + EStr]，其中 page0.slot7 与 page1.slot7 = (itemId 1, "Dao")，
//   其余 14 槽全 0/空串 → 1 + 14×6 + 2×9 = 103 B 包体 / 105 B 含 opcode。
//   紧跟在 op 198 之后、S→C op 274 ×2 之前下发（见 handler/JoinSessionReq.kt）。
//   备注：同一批里 S→C op 274 的两条正是 (页面 0/1, itemId = 角色卡, …, 0x07, 0)，
//   即再把这 8 号槽钉成当前角色卡 —— 所以 op 200 里 slot 7 填谁都无关紧要，值是占位。
//   客户端复检 0x0067593D → 0x00672746 会清掉背包里查不到的槽位，
//   并回 C→S 274 [E1 页][E1 槽][E4 0][EStr 旧名]（抓包里那两条 "Dao" 即此路径的产物，
//   decoded_dump.txt:117-118）。服务端目前对 C→S 274 只空消费（handler/Unk_274.kt）。
//
// ── 旧实现 ────────────────────────────────────────────────────────────────
// 此前是 105 B 十六进制回放常量（EN_FX_STOCK_INIT_BLOB）；2026-11-16 改为结构化生成，
// 输出与抓包逐字节一致（回归见 src/test/kotlin/bnbemu/netty/MyPageSlotsTest.kt）。
// ============================================================================

/** 每页槽位数：客户端 0x00672C50 里 `for (slot = 0; slot < 8; slot++)` 是硬编码常量，不可配置。 */
const val MY_PAGE_SLOTS_PER_PAGE = 8

/** 页数上限：客户端槽位数组固定 16 个（2 页 × 8 槽），超发即越界写坏堆。 */
const val MY_PAGE_MAX_PAGES = 2

/** 情侣槽下标；特性 2 关闭时客户端不绘制该格，解除情侣时会被清空。 */
const val MY_PAGE_COUPLE_SLOT = 7

/**
 * 一个 My Page 槽位。
 *
 * @param itemId 物品定义 SN（S→C op 60 里的 itemId），0 = 空槽
 * @param name   下发用的模型名（op 60 的 ItemDefEntry.name，如 "Dao"/"Boz"）；
 *               客户端只把它当显示令牌存下来，不参与任何校验
 */
data class MyPageSlot(
    val itemId: Int = 0,
    val name: String = "",
) {
    /** 该槽是否为空（itemId 0 即清空槽位，名字一并清掉） */
    val isEmpty: Boolean get() = itemId == 0

    companion object {
        val EMPTY = MyPageSlot()

        /** 只给 SN，模型名自动去 op 60 定义表解析（查不到的留空串）。 */
        fun of(itemId: Int): MyPageSlot =
            MyPageSlot(itemId, if (itemId == 0) "" else ItemDef.find(itemId)?.name.orEmpty())
    }
}

/**
 * S→C op 200：2 页 × 8 槽的 My Page 槽位表。
 *
 * 默认（`bomber = 0`，即抓包里的皮蛋会话）逐字节等于 cap/chan_s2c_op200_105B_0.bin：
 * 两页的 slot 7 都填该角色的角色卡（0 → itemId 1 / "Dao"），其余槽为空。
 *
 * 客户端收到后还会被同批的 S→C op 274 把 slot 7 重新钉成当前角色卡，
 * 因此 slot 7 只起占位作用；真正有内容的是 0..6 的表情/动作槽。
 */
fun MyPageSlots(bomber: Int = 0): PKT {
    val (charItemId, charName) = bomberCharCard(bomber)
    val page = List(MY_PAGE_SLOTS_PER_PAGE) { slot ->
        if (slot == MY_PAGE_COUPLE_SLOT) MyPageSlot(charItemId, charName) else MyPageSlot.EMPTY
    }
    return MyPageSlots(listOf(page, page))
}

/**
 * S→C op 200（显式槽位表）：`players[页][槽]`，页 = 玩家槽位序号，槽 0..7。
 *
 * 少于 8 槽的页按空槽补齐；超过 8 槽的多余项忽略；超过 2 页的部分直接丢弃
 * （客户端数组只有 16 槽，多写必崩）。
 */
fun MyPageSlots(players: List<List<MyPageSlot>>): PKT = PKT {
    val pages = players.take(MY_PAGE_MAX_PAGES)
    Encode1(pages.size)
    pages.forEach { page ->
        for (slot in 0 until MY_PAGE_SLOTS_PER_PAGE) {
            val entry = page.getOrNull(slot) ?: MyPageSlot.EMPTY
            Encode4(entry.itemId)
            // 必须走 CipherStr：cipherDegree=3 时客户端用 0x75DAF0 反混淆（4B/2B/1B 三段），
            // 普通 EncodeStr 会写成明文，客户端读到乱码（抓包里 "Dao" 的线上字节是 c4 2d 35）。
            EncodeCipherStr(entry.name)
        }
    }
}
