package bnbemu.packet.room

import bnbemu.netty.PKT
import bnbemu.server.Room
import bnbemu.server.state

/**
 * S→C op 155 —— 对局结束「回房间」推送（客户端据此拆掉 CGameStage，切回 stage 12 房间）。
 *
 * ## 为什么必须有这一包
 * 客户端**没有任何本地超时**会把结果窗关掉：
 *   · `0x0046F38F`（结果窗 Update）只做涨条动画，不关窗；
 *   · `0x005597FD CheckResultDoc_AndShow` 只在 `doc+6520` 倒计时归零时弹窗（`0x00558EE1`），
 *     而那个倒计时由 `0x00559F8B → 0x0053E181(delayMs - 1500)` 一次性设置 —— 也就是说
 *     **op150 的 delayMs 只决定「多久后弹结果窗」，跟「多久后回房」无关**；弹完之后再没有计时器。
 * 所以回房 100% 是服务端下令。
 *
 * ## 为什么是 op 155
 * 全 `.text` 里能在游戏阶段（stage 13）改阶段的入口只有一处：
 *   `0x0055A4D8`（局内 S→C 分发器）case 155 →
 *      `sub_67CDCE()` 为真（僵尸/团战模式）→ `0x0054A221` → `sub_68DC53(…, 11 或 20)`（回大厅/广场，并清 UI 子窗）
 *      否则（普通/练习房）            → `0x0054FC14` → 末尾 `sub_68DC53(…, 12)`（回房间）
 * `sub_68DC53`（`0x68DC53`）是全 exe 唯一的「请求切阶段」入口，而它在游戏阶段地址区间
 * （0x548000~0x55B000）里的调用点只有 `0x54FFAC` 这一处 —— 即 op155 是唯一出口。
 * ⚠️ op 97 ChangeStage 走不通：它的处理器 `0x5CDD15` 只挂在「选频道/房间」阶段的虚表
 * （0x8AF0B4 表 index 48）上，局内分发器没有 97 分支，在 stage 13 收到会被静默丢弃。
 *
 * ## 载荷（逐条由 0x0054FC14 反汇编读出）
 * ```
 * E1   chState                        -> 0x687393 (player+0x1AC)
 *      [客户端先把本地 8 个槽位清一遍: state &= 0xE3（清 0x1C，保留 0x01/0x02 的房主位与 0x20+）
 *       再把 bomber/color/map 置 -1；不读包]
 *      [特殊模式再清 10 条频道记录]
 * 3 × {                               // 0x54FCD1 循环，ebx = 0..2
 *      E1 flag
 *      flag != 0 -> ReadBuf(3)       // 本实现走这条（最短、不依赖 feature 开关）
 *                   -> 0x687169(slot, 3B) + 0x687B06(slot)  清 9 个队伍桶
 *      flag == 0 -> E4 id
 *                   CRoomInfo <- 0x4DFCE7 解析：
 *                     Str(房名) E1 E1 E4 E1 E4 E2 E4 E4 E4 E4 E1
 *                     [feature6: E4+Str | E4] E2 E2 E2 E2
 *                   ReadBuf(3) -> 0x688208(slot, roomObj, 3B)
 *                   E1 -> 0x687435(slot, …)
 *                   Str -> 0x687B3D(slot, …)
 *                   E1?… 用户列表 0x67930D/0x679450(byte1&0x3F, byte2&0x1F)/0x679271/0x6792AB×N
 * }
 * -> sub_68DC53(CStageManager, 12)
 * ```
 * 那 3 个字节 = `{state, x, y}`：`state` 就是房间槽位旗标（与 op105 SetPlayer 里 `Slot.meta[0]`
 * 同一套：0x01 关闭 / **0x02 房主** / 0x04 准备），x/y 是房间 UI 里的站位（低 6 位 / 低 5 位）。
 * 写入侧 `0x6865AC` 只用传入的 3 个字节并自己重算随机 key 与校验和，所以传什么都不会触发
 * `dword_A373C8`（那个回调只在**读回**校验失败时调用）。
 *
 * 若只靠本包回房，房间可能处于「降级」状态（没有房间对象/会话）——
 * 配合 `Room.reenterRoomAfterBack` 在 155 之后补推整条房间链（见 Room.backToRoom）。
 */
fun BackToRoom(
    chState: Int = 0,
    /** 槽 0..2 的状态字节；默认取 [Room.slots] 的真实 state（0 = 房主旗标丢失） */
    slotStates: List<Int> = defaultSlotStates()
) = PKT {
    Encode1(chState)
    repeat(3) { i ->
        Encode1(1)                      // flag != 0 -> 短分支（3 字节状态）
        Encode1(slotStates.getOrElse(i) { 0 })  // state: 0x01 关闭 / 0x02 房主 / 0x04 准备
        Encode1(0)                      // x
        Encode1(0)                      // y
    }
}

/** 默认状态：直接读 [Room.slots] 的前 3 个槽位，保住房主位（槽 0 = FLAG_CHIEF） */
private fun defaultSlotStates(): List<Int> =
    (0 until 3).map { Room.slots.getOrNull(it)?.state?.toInt() ?: 0 }
