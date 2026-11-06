package bnbemu.packet.room

import bnbemu.netty.PKT

// S→C BomberEvent (opcode 146 / 0x92) —— 局内 bomber 事件伞（★ 与古早真端 C2S 146 完全同构）
//
// 逆向定位:
//   分发表 0x55AA48(op 0x90..0xAA) 里 op 146 → 跳转桩 0x55A681 → 处理器 0x54DDD1。
//
//   E1  → al; slot = al & 0x0F; state = al >> 4      (0x54DDE4~0x54DDF6)
//   E2  → [ebp-0x10]                                 (通用头, state 2/3/4 当坐标 x 用)
//   E2  → [ebp+8]                                    (state 2/3/4 当坐标 y 用)
//   bomber = session[slot] (0x533D5C); 为空直接返回
//   if (state > 0x0D) return;  jmp [0x54E411 + state*4]
//
// 跳转表 0x54E411(14 项) 与各 state 的实际动作（反汇编 + 2026-11-05 实机验证）:
//   0  0x54DE3F  E1×3 + 取两个字段: 纯数据设置
//   1  0x54DEAA  0x45FB41 / 0x45FB4C / 0x460A4A: 与"撞到人"同一套击退处理
//   2  0x54DEE5  **困住（实机验证）**: 守卫 `[+0x4B8]==0` → vt[0x64](x,y) 设坐标
//                → vt[0xCC]=0x464289（造气泡并接管该 bomber）→ 0x54A901 刷 UI 观察者
//                ⚠️ x,y 就是气泡所在地，**必须传目标当前坐标**；传 0,0 会把角色瞬移到左上角
//                ⚠️ 它**只做视觉/位置**，不设 `+0x2AB8`，所以撞上去仍走"推挤"分支（0x10/0x20）
//   3  0x54DF1E  **脱困（实机验证）**: vt[0x64](x,y) → Decode1(saver) → vt[0xD0](saver,0)
//                → 0x54D2B8(slot, 4) 在格子上放特效。同样必须传坐标，且要补那 1 字节 saver
//   4  0x54DF68  设坐标 + 0x54D2B8 + 生成幽灵/特效
//   5  0x54E0B8  ★ E1(n) + n×(E1 slot, E2 x, E2 y)：多槽位结算/死亡名单（详见下）
//   6  0x54E149  0x45BD64 + 0x4640D9: 复活/重生
//   7  0x54E19D  vt[0xA8](-1)
//   8  0x54E1C1  E1×3 + 0x45D572: 特殊状态设置
//   9  0x54E20F  0x45E923 → **开护盾**（实测：目标套上光环但还能走动）
//                `[+0x2AB8] = [+0x2064]`（护盾总帧数），播 0x86 = 开盾音
//  10  0x54E21B  0x45E998 → **收护盾**：`+0x2AB8 = min(+0x2AB8, +0x2070)`，播 0x87 = 收盾音
//                玩家撞到带盾目标时客户端本地先跑它，再发 `C→S op112 action=0xA0 | slot`
//
// ★ `bomber+0x2AB8` 是**护盾剩余帧数**，不是"被困计时"（2026-11-06 钉死）：
//   · 上限 `+0x2064`、闪烁阈值 `+0x2070` —— 开局由客户端按地图信息自己算好
//     （`0x54D4AC` 被 0x553FD6 / 0x55889B / 0x5589E8 调用，值来自 mapinfo 的 `[+0xc4]/30`、`[+0xb8]/30`），
//     服务端只负责"何时开/何时收"，不用传时长。
//   · 渲染 `0x465891`：`+0x2AB8 <= +0x2070` → 画 `+0x9C4` = `Item/ShieldFlash`（绑定于 0x463186），
//     否则 `<= +0x2064` → 画 `+0x94C` = `Item/ShieldOn`（绑定于 0x463170）。这就是那圈"光环"。
//   · 帧更新 `0x4689CE`：`+0x2AB8 == +0x2070` 时停 0x86 循环音、播 0x85（进入闪烁阶段）。
//   · **0x87 是收盾音，和死亡音无关**（死亡是名单结尾的 0x2A、op159 的 0x37）。
//     但 `0x45E998` 无条件播它，所以给**没有护盾**的槽位发 state 10 会白响一声 —— 服务端必须用
//     `Slot.hasShield` 拦住（见 Room.breakShield）。
//   · 它**不会**让客户端免疫水柱：水柱命中是本地 `0x4611C3 → 0x45E8AF` 直接起泡泡，
//     护盾只改 `+0x2AB8`；"免疫一次水柱"要服务端自己判。
//  11  0x54E227  按 E1 取另一个 bomber + 传送/换位
//  12  0x54DF57  **脱困但不改坐标（实机验证）**: 只 vt[0xD0](0,0)，不设位置 → 角色留在原地
//  13  0x54E34E  **复活卡特效（实机验证：对别人用）**: 特效 + 0x45FBD3 + 0x671102/0x677912
//
// ★ state 5 = 官方服务端的"死亡名单"，与古早真端 FxServer 的
//   `S2C 159 OnBomberEvent: E1(0x50) E2(0) E2(0) E1(n) + n×(E1 slot, E2 9999, E2 9999)`
//   **逐字节一致**。处理器 0x54E0B8 的循环体：
//     Decode1 slot2 / Decode2 x / Decode2 y
//     bomber = session[slot2]
//     if (x != 9999 && y != 9999) bomber->vt[0x64](x, y)     // 9999 = DEATH_COORD 哨兵
//     0x548717(slot2)   // 从场上移除（vt[0xC8] + 队伍判定）
//     0x54A8A6(slot2)   // 遍历 UI 观察者 vt[0x30](slot) 刷新
//   循环结束后 `0x4DEDAD(0x2A, 0, 0)` 播音效 **0x2A**。
//   → 真正"有人死了"的广播是这一条；单字节 op159 只置状态 17 并播 0x37（实测无声）。
//
// ⚠️ 客户端本地被水柱打中（0x4611C3）只调 0x45E8AF(bomber,500) 做泡泡视觉 + 死亡倒计时，
//    全程**不发包**，所以"谁被困 / 谁死了"必须由服务端决定并用 state 2 / state 5 广播。
//
// 结构 (通用头 5 字节):
//   E1((state << 4) | slotId)
//   E2(x)
//   E2(y)
// 追加: state 3 需要 1 字节 saverSlot；state 5 则是 E1(count) + count × [E1 slot, E2 x, E2 y]。

const val BOMBER_STATE_TRAPPED = 2       // 困住（气泡视觉）：需要目标当前坐标 (x, y)
const val BOMBER_STATE_SAVED = 3         // 被救脱困：需要坐标 + 1 字节 saver
const val BOMBER_STATE_DEATH_LIST = 5    // 死亡名单（官方服务端真正用的那条）
const val BOMBER_STATE_REVIVE = 6        // 复活/重生
const val BOMBER_STATE_SHIELD = 9        // 开护盾：`+0x2AB8 = +0x2064`，音效 0x86
const val BOMBER_STATE_SHIELD_END = 10   // 收护盾：`+0x2AB8 = min(..., +0x2070)`，音效 0x87（不是死亡音）
const val BOMBER_STATE_FREE = 12         // 脱困但不改坐标（角色不会瞬移）
const val BOMBER_STATE_REVIVE_CARD = 13  // 复活卡特效（对别人使用）

/** DEATH_COORD 哨兵：客户端用它表示"没有坐标可设" */
const val BOMBER_DEATH_COORD = 9999

/**
 * @param x/y       只有 state 2/3/4 用到，必须是目标 bomber 的当前像素坐标。
 *                  传 0 会让客户端把角色瞬移到 (0,0) —— 这就是"发了 trap 之后人物消失"的原因。
 * @param saverSlot 只有 state 3 用到（被谁救的），必须 >= 0 时才追加该字节。
 */
fun SetBomberState(slotId: Int, state: Int, x: Int = 0, y: Int = 0, saverSlot: Int = -1) = PKT {
    Encode1(((state and 0x0F) shl 4) or (slotId and 0x0F))
    Encode2(x)
    Encode2(y)
    if (saverSlot >= 0) Encode1(saverSlot and 0x0F)
}

/**
 * state = 5 的"死亡名单"变体：一次广播多个槽位阵亡。
 *
 *   通用头低 4 位 [headerSlot] **不是摆设**：客户端 0x54DDD1 解完 E1 + E2 + E2 后
 *   先 `sub_533D5C(headerSlot)` 取 bomber（0x533D5C: `slot >= 当前bomber数`
 *   或 `数组[slot] == 0` → 返回空），取不到就 `if (v6 != nullptr)` 不成立，
 *   **整个 switch 被跳过** —— case5 的摘除、UI 刷新、末尾的 0x2A 死亡音全部丢失。
 *   古早真端固定发 0x50 是因为它的 slot 0 恒为房主；模拟器里玩家未必坐 slot 0，
 *   且 slot 0 阵亡后可能已被摘除，所以通用头必须跟随名单动态选，不能写死 0。
 *   取「本次名单里第一个槽」最稳：判空发生在 case5 循环摘除**之前**，
 *   名单里的 bomber 此刻必定还在；不依赖 AI bomber 是否也在这个数组里。
 *   玩家坐 slot0 且阵亡时，这样算出来仍是最常见的 0x50，与官方包一致。
 *
 * @param headerSlot 通用头的 slot；默认取名单第一个槽，调用方不必关心。
 *                   需要复现官方固定 0x50 抓包时显式传 0。
 */
fun BomberDeathList(
    slots: List<Int>,
    x: Int = BOMBER_DEATH_COORD,
    y: Int = BOMBER_DEATH_COORD,
    headerSlot: Int = slots.firstOrNull() ?: 0
) = PKT {
    Encode1(((BOMBER_STATE_DEATH_LIST and 0x0F) shl 4) or (headerSlot and 0x0F))
    Encode2(0)
    Encode2(0)
    Encode1(slots.size and 0x0F)
    slots.forEach {
        Encode1(it and 0x0F)
        Encode2(x)
        Encode2(y)
    }
}
