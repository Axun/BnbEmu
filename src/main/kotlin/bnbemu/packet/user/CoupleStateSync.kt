package bnbemu.packet.user

import bnbemu.netty.PKT

// ============================================================================
// S→C op 247 (0xF7)：CCoupleManager 情侣/伴侣状态全量同步（进频道握手推送 #16, seq=145）
// ============================================================================
// ── 命名与分派链（地址实证）────────────────────────────────────────────────
//   sub_502AE1（大厅/通用 S→C 二级分派器，0x00502AE1）在 0xD2 < op <= 0xF8 的 switch 内：
//     case 247: sub_69C829((_DWORD *)dword_993E74, a2); break;      // CA.exe.c:255880
//   dword_993E74 = TSingleton<CCoupleManager>（构造 0x0069A35B / 0x0069BF4D 里
//   `*this = &TSingleton<CCoupleManager>::vftable'; dword_993E74 = (int)this;`，
//   RTTI vftable 0x008B98B0 / 0x008B98B8），所以本包就是情侣管理器的状态同步。
//   同一单例还处理 op 248/249/250/251/252（sub_69A5E7 / sub_69CABE / sub_69C9DD /
//   sub_69CA8A / sub_69ADCC），那些是单槽增量更新；本包是**全量替换**。
//
// ── 全量语义（sub_69C829, 0x0069C829）─────────────────────────────────────
//   函数第一步 sub_69A9DF(this) → 把 2 个本机玩家槽逐槽 sub_69A9A8 复位
//   （伴侣名清空、state/level 归 0、周年串清空），随后才按包体重填。
//   因此**每次进频道 / 进房都必须重发**，漏发会让客户端保留上一局的伴侣状态。
//
// ── 包体结构 ──────────────────────────────────────────────────────────────
//   E1(count) + count × [
//     E1(state)                                → CCoupleManager[46+slot]、CUDI+698
//     state != 0 时：E1(level) + Str(伴侣名)    → CCoupleManager[48+slot] / +696
//                                                 名字存 this[34+6*slot]（std::string）
//     E2(anniv)                                → 周年礼物件数/档位
//     anniv != 0 时：Str(礼物/祝福语)           → CCoupleAnniversaryCongratulateDlg
//   ]
//
// ── 字段语义 ──────────────────────────────────────────────────────────────
//   count：本机玩家槽数（CCoupleManager 固定 2 槽，getter 一律 `if (idx > 1)`），
//          抓包只发 1（单开）。超过 2 会让客户端 `this[46+slot]` 写到槽外，必须 clamp。
//   state：0 = 无伴侣（跳过后面的 level + 名字）
//          1 = 正常伴侣（op 249 的普通分支写入）
//          2 = 关系解除 → sub_69AFAD 弹 LeaveCouple2 / LeaveCouple3 并复位该槽
//          4 = 特殊地区分支（sub_69CABE 里 `dword_993E54+5 == 13` 时写入，求婚/申请流程）
//   level：1..6，客户端读入时「0 归 1、>6 钳 6」（sub_69C829 内联钳制）；
//          写入 CUserDynamicInfo+696，UI 用它查 CoupleExpBonus / CoupleLucciBonus
//          （sub_69A28A → `unk_9DC9E8 + 6*level`）、情人节 ValentineEventHeart，
//          为 0 时点情侣页签弹 NotHaveCouple（0x00449942）。
//   anniv：非 0 时以 "CoupleAnniversaryGift%d" 拼资源名（sub_69CF52, 0x0069CF52），
//          构造 CCoupleAnniversaryCongratulateDlg 并立即显示，随后紧跟一条祝福串。
//   Str：长度前缀恒 E2（sub_78FE10 → sub_78F910, 0x0078F910 用 Decode2 读长度），
//        内容是 0x75DAF0 令牌变换（本实现用 EncodeCipherStr，与客户端 degree 1..3 对偶）。
//
// ── 抓包基准 ──────────────────────────────────────────────────────────────
//   cap/chan_s2c_op247_6B_0.bin     = a5 9e | 5b 5a a5 69   （6 B，前 2 B 为 opcode）
//   cap/practice_s2c_op247_4B_0.bin = 5b 5a a5 69           （仅包体）
//   解码：E1(1) 本机 1 槽 → E1(0) 无伴侣（跳过 level+名字）→ E2(0) 无周年礼（跳过祝福串）
//   = 「未婚且无纪念日」的空状态，因此一个对话框都不会弹。模拟器默认发这个即可。
//
// ============================================================================

/** CCoupleManager 只有 2 个本机玩家槽；超出会让客户端 `this[46+slot]` 写越界。 */
const val COUPLE_MAX_SLOTS = 2

/** 情侣等级上限：客户端读入时 `if (level > 6) level = 6`。 */
const val COUPLE_MAX_LEVEL = 6

/**
 * 一个本机玩家槽的情侣状态。
 *
 * @param state   0 = 无伴侣；1 = 正常；2 = 关系解除（弹 LeaveCouple）；4 = 特殊地区分支
 * @param level   情侣等级 1..6（仅 state != 0 时下发），0 会被双方钳成 1
 * @param partnerName 伴侣名（仅 state != 0 时下发，令牌编码字符串）
 * @param anniversary 周年礼物件数/档位，0 = 无周年祝贺
 * @param anniversaryMessage 周年祝福语（仅 anniversary != 0 时下发）
 */
data class CoupleSlot(
    val state: Int = 0,
    val level: Int = 0,
    val partnerName: String = "",
    val anniversary: Int = 0,
    val anniversaryMessage: String = "",
)

/**
 * S→C op 247：情侣/伴侣状态全量同步。
 *
 * 默认 `CoupleStateSync()` = 抓包里的空状态（1 个本机玩家、无伴侣、无周年礼），
 * 逐字节等于 cap/chan_s2c_op247_6B_0.bin 去掉 2 B opcode 后的 `5b 5a a5 69`。
 *
 * 客户端收到后先清空两个玩家槽再按此重填，所以这是「本次登录的情侣真相」，
 * 不是增量；增量走 op 248/249/250/251/252。
 */
fun CoupleStateSync(
    slots: List<CoupleSlot> = listOf(CoupleSlot()),
): PKT {
    require(slots.size <= COUPLE_MAX_SLOTS) {
        "CCoupleManager 只有 $COUPLE_MAX_SLOTS 个本机玩家槽，count=${slots.size} 会写越界"
    }
    return PKT {
        Encode1(slots.size)
        slots.forEach { slot ->
            Encode1(slot.state)
            // state == 0 时客户端整段跳过，多写 level/名字会让后续记录错位
            if (slot.state != 0) {
                Encode1(slot.level.coerceIn(1, COUPLE_MAX_LEVEL))
                EncodeCipherStr(slot.partnerName)
            }
            Encode2(slot.anniversary)
            // anniv == 0 时同上，客户端不会读这条祝福串
            if (slot.anniversary != 0) {
                EncodeCipherStr(slot.anniversaryMessage)
            }
        }
    }
}
