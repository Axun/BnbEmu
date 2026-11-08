package bnbemu.handler.room

import bnbemu.debugMode
import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler
import bnbemu.server.Room

// C→S (EN op 107 / 0x6B) —— 局内「水泡移动（抛掷/踢出）」上报请求。
//
// 逆向结论 (CA.exe, Locale=EN / v18, 复现脚本 tools/rev_op107.py, 2026-11-09 定案):
//   * 发包点全 .text 唯一: 0x00539DF6 (`push 0x6b` + COutPacket::Init 0x64EE5D),
//     发包函数 0x00539DC8 由 CGameMsgCache::OnTick (0x0053A700) 每帧 flush,
//     与 op 109 推箱子同一套「待确认队列 [cache+0x80]+idx*0x20 + 每帧 flush」机制。
//   * 载荷 9B = E1((kind<<4)|slot) E2(bombId) E1(fromX) E1(fromY) E1(toX) E1(toY) E2(ms)
//     - kind 4..7 = 踢出/滑行 (0x0054CCBC 路径, kind = 朝向+4, ms 写死 1000)
//     - kind 0..3 = 触碰推行/抛掷 (0x0046E6D7 所在 bomber 帧更新路径, ms 由 trace 给出)
//     - bombId = op 139 里回填的 bomb->m_nBombId (+0x90) = 我们的 bombToken
//     - 坐标序 = (gridX 行 0..12, gridY 列 0..14), 与 op 141 / op 106 同一字段序
//       (方向增量表 dword_9886E4/9886E8[2*dir] 交叉验证: kind=6→dir=2 走 gridY+1)
//   * 客户端发包链只做 trace + 入队, **没有本地位移预测**; 水泡位移的唯一驱动是
//     S→C op 141 (BombIgnite, 0x54BD14) 的 action=kind 分支:
//       action 4..7 → sub_4598AB 滑行 (moving=1, 方向=action-4, 帧更新 0x459C69 逐帧滑到目标格)
//       action 0..3 → sub_459935 重定位/抛掷落地 (帧更新 0x459EEB 播弧线)
//     两个函数全 exe 只有 op141 处理器与 op139 确认链两个调用者 —— 不回 op 141 水泡纹丝不动。
//   * 回显载荷 = 本包 9B 去掉末尾 E2(ms) 的前 7 字节原样 (op 141 客户端只读 7 字节,
//     末两字节即目标格, 转发给 sub_4598AB/sub_459935), 广播给所有人含踢泡者本人。
//   * ⚠️ 不要回 op 107: EN 局内 S→C 分发器 0x0055A4D8 没有 0x6B 分支, 回显会被静默丢弃。
val BombKickThrowReq = PKTHandler { c ->
    if (readableBytes() < 9) {
        skipBytes(readableBytes())
        return@PKTHandler
    }

    val kindSlot = Decode1
    val slotId = kindSlot and 0x0F
    val kind = kindSlot shr 4
    val bombId = Decode2
    val fromX = Decode1
    val fromY = Decode1
    val toX = Decode1
    val toY = Decode1
    val moveDuration = Decode2

    if (debugMode) {
        println("[op107] kind=$kind slot=$slotId bomb=$bombId ($fromX,$fromY)->($toX,$toY) ${moveDuration}ms")
    }

    Room.onBombMove(slotId, kind, bombId, fromX, fromY, toX, toY, moveDuration)

    // 防御式消费: 若未来客户端追加字段，不让残留字节影响下一包解析
    if (readableBytes() > 0) {
        skipBytes(readableBytes())
    }
}
